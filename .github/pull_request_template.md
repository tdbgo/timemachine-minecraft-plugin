## Summary

Describe the behavior change and why it is needed.

## Verification

- [ ] `clean build` passes with the Gradle Wrapper.
- [ ] New behavior and failure paths have regression tests.
- [ ] Stored data and configuration remain backward-compatible, or the migration is documented and tested.
- [ ] Bukkit world access stays on the correct server thread.
- [ ] Filesystem, hashing, archive scanning, and JDBC work stay off the server thread.
- [ ] Documentation and `CHANGELOG.md` are updated when behavior changes.
- [ ] No build output, local database, server data, logs, or IDE state is included.

## Data-safety impact

Explain the commit order, rollback behavior, and any paths this change may create, move, overwrite, or delete.
