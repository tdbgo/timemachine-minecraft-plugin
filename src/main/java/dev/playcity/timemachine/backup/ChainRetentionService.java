package dev.playcity.timemachine.backup;

import dev.playcity.timemachine.i18n.LocalizedMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class ChainRetentionService {
    private static final String PROPERTIES_FILE = "snapshot.properties";
    private static final int MAX_SCAN_DEPTH = 4;

    private final Path snapshotsRoot;
    private final Path trashRoot;
    private final SnapshotStore snapshotStore;

    public ChainRetentionService(Path storageRoot, List<Path> archiveRoots) {
        Path normalizedStorage = storageRoot.toAbsolutePath().normalize();
        this.snapshotsRoot = normalizedStorage.resolve("snapshots");
        this.trashRoot = normalizedStorage.resolve("retention-trash");
        this.snapshotStore = new SnapshotStore(normalizedStorage, archiveRoots);
    }

    public PrunePlan plan(Policy policy, String protectedBaseSnapshotId, Instant plannedAt) throws IOException {
        return computePlan(policy, safe(protectedBaseSnapshotId), plannedAt).publicPlan();
    }

    public PruneResult apply(PrunePlan expectedPlan, Policy policy, String protectedBaseSnapshotId)
            throws IOException {
        ComputedPlan current = computePlan(policy, safe(protectedBaseSnapshotId), expectedPlan.plannedAt());
        PrunePlan currentPlan = current.publicPlan();
        if (!expectedPlan.fingerprint().equals(currentPlan.fingerprint())
                || !expectedPlan.token().equals(currentPlan.token())) {
            throw new IOException("Snapshot inventory changed after the prune plan was created; request a new plan.");
        }
        if (current.deleteChainData().isEmpty()) {
            return new PruneResult(0, 0, 0L, null);
        }

        verifyRetainedChains(current.retainedChainData());
        return moveThenDelete(current.deleteChainData(), currentPlan);
    }

    private ComputedPlan computePlan(Policy policy, String protectedBaseSnapshotId, Instant plannedAt)
            throws IOException {
        validatePolicy(policy);
        Map<String, SnapshotNode> snapshots = scanSnapshots();
        List<ChainData> chains = buildChains(snapshots);
        Set<String> knownSnapshotIds = Set.copyOf(snapshots.keySet());
        List<LocalizedMessage> warnings = policy.pinnedSnapshotIds().stream()
                .filter(id -> !knownSnapshotIds.contains(id))
                .sorted()
                .map(id -> LocalizedMessage.of("retention.warning.pinned_missing", id))
                .toList();

        List<ChainData> oldestFirst = chains.stream()
                .sorted(Comparator.comparing(ChainData::newestAt).thenComparing(ChainData::baseSnapshotId))
                .toList();
        int remainingChains = oldestFirst.size();
        int countFloor = policy.maxChains() == 0
                ? Integer.MAX_VALUE
                : Math.max(policy.maxChains(), policy.minimumChains());
        Instant ageCutoff = policy.maxAgeDays() == 0
                ? Instant.MIN
                : plannedAt.minus(policy.maxAgeDays(), ChronoUnit.DAYS);
        List<ChainData> deleteChains = new ArrayList<>();
        List<ChainData> retainedChains = new ArrayList<>();

        for (ChainData chain : oldestFirst) {
            boolean protectedChain = chain.baseSnapshotId().equals(protectedBaseSnapshotId)
                    || chain.nodes().stream().anyMatch(node -> policy.pinnedSnapshotIds().contains(node.id()));
            boolean overCount = policy.maxChains() > 0 && remainingChains > countFloor;
            boolean overAge = policy.maxAgeDays() > 0 && chain.newestAt().isBefore(ageCutoff);
            boolean aboveSafetyFloor = remainingChains > policy.minimumChains();
            if (!protectedChain && aboveSafetyFloor && (overCount || overAge)) {
                String reason;
                if (overCount && overAge) {
                    reason = "count+age";
                } else {
                    reason = overCount ? "count" : "age";
                }
                deleteChains.add(chain.withReason(reason));
                remainingChains--;
            } else {
                retainedChains.add(chain.withProtected(protectedChain));
            }
        }

        retainedChains.sort(Comparator.comparing(ChainData::newestAt).reversed());
        String fingerprint = fingerprint(policy, protectedBaseSnapshotId, plannedAt, chains, deleteChains);
        String token = fingerprint.substring(0, 12);
        List<ChainPlan> publicDeletes = deleteChains.stream().map(ChainData::toPublicPlan).toList();
        List<ChainPlan> publicRetained = retainedChains.stream().map(ChainData::toPublicPlan).toList();
        long reclaimableBytes = sumBytes(deleteChains);
        PrunePlan publicPlan = new PrunePlan(
                token,
                plannedAt,
                fingerprint,
                publicDeletes,
                publicRetained,
                publicDeletes.stream().mapToInt(ChainPlan::snapshots).sum(),
                reclaimableBytes,
                warnings);
        return new ComputedPlan(publicPlan, List.copyOf(deleteChains), List.copyOf(retainedChains));
    }

    private Map<String, SnapshotNode> scanSnapshots() throws IOException {
        if (!Files.isDirectory(snapshotsRoot)) {
            return Map.of();
        }
        Map<String, SnapshotNode> snapshots = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.find(
                snapshotsRoot,
                MAX_SCAN_DEPTH,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().equals(PROPERTIES_FILE))) {
            for (Path propertiesPath : stream.sorted().toList()) {
                checkInterrupted();
                SnapshotNode node = readSnapshot(propertiesPath);
                if (snapshots.putIfAbsent(node.id(), node) != null) {
                    throw new IOException("Duplicate local snapshot ID prevents safe pruning: " + node.id());
                }
            }
        }
        return Map.copyOf(snapshots);
    }

    private SnapshotNode readSnapshot(Path propertiesPath) throws IOException {
        Path snapshotDirectory = propertiesPath.getParent().toAbsolutePath().normalize();
        if (snapshotDirectory.equals(snapshotsRoot)
                || !snapshotDirectory.startsWith(snapshotsRoot)
                || Files.isSymbolicLink(snapshotDirectory)) {
            throw new IOException("Invalid snapshot directory prevents safe pruning: " + snapshotDirectory);
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesPath)) {
            properties.load(input);
        }
        if (!SnapshotStore.FORMAT_VERSION.equals(properties.getProperty("format.version"))) {
            throw new IOException("Unsupported snapshot format prevents safe pruning: " + snapshotDirectory);
        }
        String id = requireValue(properties, "snapshot.id", snapshotDirectory);
        String baseId = requireValue(properties, "base.snapshot.id", snapshotDirectory);
        String parentId = properties.getProperty("parent.snapshot.id", "");
        SnapshotKind kind;
        Instant createdAt;
        try {
            kind = SnapshotKind.valueOf(requireValue(properties, "snapshot.kind", snapshotDirectory));
            createdAt = Instant.parse(requireValue(properties, "created.at", snapshotDirectory));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid snapshot metadata prevents safe pruning: " + snapshotDirectory, ex);
        }
        long bytes = directorySizeWithoutLinks(snapshotDirectory);
        return new SnapshotNode(id, baseId, parentId, kind, createdAt, snapshotDirectory, bytes);
    }

    private String requireValue(Properties properties, String key, Path snapshotDirectory) throws IOException {
        String value = properties.getProperty(key, "");
        if (value.isBlank()
                || value.length() > 256
                || value.indexOf('\0') >= 0
                || value.indexOf('\t') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IOException("Missing or invalid " + key + " in " + snapshotDirectory);
        }
        return value;
    }

    private long directorySizeWithoutLinks(Path snapshotDirectory) throws IOException {
        long total = 0L;
        try (Stream<Path> stream = Files.walk(snapshotDirectory)) {
            for (Path path : stream.toList()) {
                checkInterrupted();
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Symbolic links prevent safe pruning: " + path);
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        total = Math.addExact(total, Files.size(path));
                    } catch (ArithmeticException ex) {
                        throw new IOException("Snapshot size exceeds the supported range: " + snapshotDirectory, ex);
                    }
                }
            }
        }
        return total;
    }

    private List<ChainData> buildChains(Map<String, SnapshotNode> snapshots) throws IOException {
        Map<String, List<SnapshotNode>> byBase = new LinkedHashMap<>();
        snapshots.values().stream()
                .sorted(Comparator.comparing(SnapshotNode::createdAt).thenComparing(SnapshotNode::id))
                .forEach(node -> byBase.computeIfAbsent(node.baseSnapshotId(), ignored -> new ArrayList<>()).add(node));
        List<ChainData> chains = new ArrayList<>();
        for (Map.Entry<String, List<SnapshotNode>> entry : byBase.entrySet()) {
            String baseId = entry.getKey();
            List<SnapshotNode> nodes = List.copyOf(entry.getValue());
            Map<String, SnapshotNode> nodesById = new LinkedHashMap<>();
            nodes.forEach(node -> nodesById.put(node.id(), node));
            SnapshotNode base = nodesById.get(baseId);
            if (base == null || base.kind() != SnapshotKind.FULL || !base.parentSnapshotId().isBlank()) {
                throw new IOException("Incomplete FULL chain prevents safe pruning: " + baseId);
            }
            for (SnapshotNode node : nodes) {
                validateParentChain(node, baseId, nodesById);
            }
            Instant oldestAt = nodes.stream().map(SnapshotNode::createdAt).min(Instant::compareTo).orElseThrow();
            Instant newestAt = nodes.stream().map(SnapshotNode::createdAt).max(Instant::compareTo).orElseThrow();
            long bytes = sumNodeBytes(nodes);
            chains.add(new ChainData(baseId, oldestAt, newestAt, bytes, nodes, false, "retained"));
        }
        return List.copyOf(chains);
    }

    private void validateParentChain(
            SnapshotNode start,
            String expectedBaseId,
            Map<String, SnapshotNode> nodesById) throws IOException {
        Set<String> visited = new LinkedHashSet<>();
        SnapshotNode current = start;
        while (true) {
            if (!visited.add(current.id())) {
                throw new IOException("Snapshot chain cycle prevents safe pruning at " + current.id());
            }
            if (current.kind() == SnapshotKind.FULL) {
                if (!current.id().equals(expectedBaseId) || !current.parentSnapshotId().isBlank()) {
                    throw new IOException("Invalid FULL baseline prevents safe pruning: " + current.id());
                }
                return;
            }
            if (current.parentSnapshotId().isBlank()) {
                throw new IOException("Snapshot has no parent and prevents safe pruning: " + current.id());
            }
            current = nodesById.get(current.parentSnapshotId());
            if (current == null) {
                throw new IOException("Missing parent prevents safe pruning for snapshot: " + start.id());
            }
        }
    }

    private void verifyRetainedChains(List<ChainData> retainedChains) throws IOException {
        for (ChainData chain : retainedChains) {
            checkInterrupted();
            Set<String> parentIds = chain.nodes().stream()
                    .map(SnapshotNode::parentSnapshotId)
                    .filter(parent -> !parent.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            List<String> leafIds = chain.nodes().stream()
                    .map(SnapshotNode::id)
                    .filter(id -> !parentIds.contains(id))
                    .toList();
            for (String leafId : leafIds) {
                checkInterrupted();
                SnapshotStore.VerificationResult verification = snapshotStore.verifySnapshot(leafId);
                if (!verification.valid()) {
                    throw new IOException("Retained chain verification failed for " + leafId + ": "
                            + String.join("; ", verification.errors()));
                }
            }
        }
    }

    private PruneResult moveThenDelete(List<ChainData> deleteChains, PrunePlan plan) throws IOException {
        Files.createDirectories(trashRoot);
        if (Files.isSymbolicLink(trashRoot)) {
            throw new IOException("Retention trash root must not be a symbolic link: " + trashRoot);
        }
        Path trash = trashRoot.resolve("prune-" + plan.token() + "-" + UUID.randomUUID()).normalize();
        if (!trash.getParent().equals(trashRoot)) {
            throw new IOException("Invalid retention trash path.");
        }
        Files.createDirectory(trash);

        List<SnapshotNode> nodes = deleteChains.stream()
                .flatMap(chain -> chain.nodes().stream())
                .sorted(Comparator.comparing(SnapshotNode::createdAt).reversed())
                .toList();
        List<MovedSnapshot> moved = new ArrayList<>();
        List<String> manifest = new ArrayList<>();
        manifest.add("snapshotId\toriginalPath\ttrashPath");
        try {
            for (int index = 0; index < nodes.size(); index++) {
                checkInterrupted();
                SnapshotNode node = nodes.get(index);
                validateDeleteTarget(node);
                Path target = trash.resolve(String.format("%04d", index + 1));
                manifest.add(node.id() + "\t" + node.path() + "\t" + target);
            }
            Files.write(trash.resolve("retention-manifest.tsv"), manifest, StandardCharsets.UTF_8);
            for (int index = 0; index < nodes.size(); index++) {
                checkInterrupted();
                SnapshotNode node = nodes.get(index);
                Path target = trash.resolve(String.format("%04d", index + 1));
                move(node.path(), target);
                moved.add(new MovedSnapshot(node.path(), target));
            }
        } catch (IOException ex) {
            if (rollbackMoves(moved, ex)) {
                try {
                    deleteTreeIfOwned(trash);
                } catch (IOException cleanupFailure) {
                    ex.addSuppressed(cleanupFailure);
                }
            } else {
                ex.addSuppressed(new IOException(
                        "Rollback was incomplete; retained snapshot data remains in retention trash: " + trash));
            }
            throw ex;
        }

        Path recoverableTrash = null;
        try {
            deleteTreeIfOwned(trash);
        } catch (IOException ex) {
            recoverableTrash = trash;
        }
        return new PruneResult(
                deleteChains.size(),
                nodes.size(),
                recoverableTrash == null ? plan.reclaimableBytes() : 0L,
                recoverableTrash);
    }

    private void validateDeleteTarget(SnapshotNode node) throws IOException {
        Path target = node.path().toAbsolutePath().normalize();
        if (target.equals(snapshotsRoot)
                || !target.startsWith(snapshotsRoot)
                || Files.isSymbolicLink(target)
                || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Snapshot deletion target changed or is unsafe: " + node.path());
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(target.resolve(PROPERTIES_FILE))) {
            properties.load(input);
        }
        if (!node.id().equals(properties.getProperty("snapshot.id"))) {
            throw new IOException("Snapshot deletion target identity changed: " + node.path());
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    private boolean rollbackMoves(List<MovedSnapshot> moved, IOException originalFailure) {
        boolean complete = true;
        for (MovedSnapshot snapshot : moved.reversed()) {
            try {
                move(snapshot.trashPath(), snapshot.originalPath());
            } catch (IOException rollbackFailure) {
                complete = false;
                originalFailure.addSuppressed(rollbackFailure);
            }
        }
        return complete;
    }

    private void deleteTreeIfOwned(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(trashRoot)
                || !normalized.getFileName().toString().startsWith("prune-")
                || Files.isSymbolicLink(normalized)) {
            throw new IOException("Refusing to delete an unowned retention path: " + directory);
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(normalized)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private String fingerprint(
            Policy policy,
            String protectedBaseSnapshotId,
            Instant plannedAt,
            List<ChainData> allChains,
            List<ChainData> deleteChains) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
        updateDigest(digest, plannedAt.toString());
        updateDigest(digest, protectedBaseSnapshotId);
        updateDigest(digest, Integer.toString(policy.maxChains()));
        updateDigest(digest, Integer.toString(policy.maxAgeDays()));
        updateDigest(digest, Integer.toString(policy.minimumChains()));
        policy.pinnedSnapshotIds().stream().sorted().forEach(id -> updateDigest(digest, id));
        Set<String> deleting = deleteChains.stream()
                .map(ChainData::baseSnapshotId)
                .collect(java.util.stream.Collectors.toSet());
        for (ChainData chain : allChains.stream().sorted(Comparator.comparing(ChainData::baseSnapshotId)).toList()) {
            updateDigest(digest, chain.baseSnapshotId());
            updateDigest(digest, Boolean.toString(deleting.contains(chain.baseSnapshotId())));
            for (SnapshotNode node : chain.nodes().stream().sorted(Comparator.comparing(SnapshotNode::id)).toList()) {
                updateDigest(digest, node.id());
                updateDigest(digest, node.parentSnapshotId());
                updateDigest(digest, node.kind().name());
                updateDigest(digest, node.createdAt().toString());
                updateDigest(digest, node.path().toString());
                updateDigest(digest, Long.toString(node.bytes()));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private long sumNodeBytes(List<SnapshotNode> nodes) throws IOException {
        long result = 0L;
        for (SnapshotNode node : nodes) {
            try {
                result = Math.addExact(result, node.bytes());
            } catch (ArithmeticException ex) {
                throw new IOException("Snapshot chain size exceeds the supported range.", ex);
            }
        }
        return result;
    }

    private long sumBytes(List<ChainData> chains) throws IOException {
        long result = 0L;
        for (ChainData chain : chains) {
            try {
                result = Math.addExact(result, chain.bytes());
            } catch (ArithmeticException ex) {
                throw new IOException("Prune size exceeds the supported range.", ex);
            }
        }
        return result;
    }

    private void validatePolicy(Policy policy) {
        if (policy.minimumChains() < 2
                || policy.maxChains() < 0
                || (policy.maxChains() > 0 && policy.maxChains() < policy.minimumChains())
                || policy.maxAgeDays() < 0) {
            throw new IllegalArgumentException("Invalid chain retention policy.");
        }
    }

    private void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Retention operation was interrupted.");
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record Policy(
            int maxChains,
            int maxAgeDays,
            int minimumChains,
            Set<String> pinnedSnapshotIds) {
        public Policy {
            pinnedSnapshotIds = Set.copyOf(pinnedSnapshotIds);
        }
    }

    public record PrunePlan(
            String token,
            Instant plannedAt,
            String fingerprint,
            List<ChainPlan> deleteChains,
            List<ChainPlan> retainedChains,
            int snapshotsToDelete,
            long reclaimableBytes,
            List<LocalizedMessage> warnings) {
        public PrunePlan {
            deleteChains = List.copyOf(deleteChains);
            retainedChains = List.copyOf(retainedChains);
            warnings = List.copyOf(warnings);
        }
    }

    public record ChainPlan(
            String baseSnapshotId,
            Instant oldestAt,
            Instant newestAt,
            int snapshots,
            long bytes,
            boolean protectedChain,
            String reason,
            List<String> snapshotIds) {
    }

    public record PruneResult(
            int deletedChains,
            int deletedSnapshots,
            long reclaimedBytes,
            Path recoverableTrash) {
    }

    private record SnapshotNode(
            String id,
            String baseSnapshotId,
            String parentSnapshotId,
            SnapshotKind kind,
            Instant createdAt,
            Path path,
            long bytes) {
    }

    private record ChainData(
            String baseSnapshotId,
            Instant oldestAt,
            Instant newestAt,
            long bytes,
            List<SnapshotNode> nodes,
            boolean protectedChain,
            String reason) {
        private ChainData withProtected(boolean value) {
            return new ChainData(baseSnapshotId, oldestAt, newestAt, bytes, nodes, value, reason);
        }

        private ChainData withReason(String value) {
            return new ChainData(baseSnapshotId, oldestAt, newestAt, bytes, nodes, protectedChain, value);
        }

        private ChainPlan toPublicPlan() {
            return new ChainPlan(
                    baseSnapshotId,
                    oldestAt,
                    newestAt,
                    nodes.size(),
                    bytes,
                    protectedChain,
                    reason,
                    nodes.stream().map(SnapshotNode::id).sorted().toList());
        }
    }

    private record ComputedPlan(
            PrunePlan publicPlan,
            List<ChainData> deleteChainData,
            List<ChainData> retainedChainData) {
    }

    private record MovedSnapshot(Path originalPath, Path trashPath) {
    }
}
