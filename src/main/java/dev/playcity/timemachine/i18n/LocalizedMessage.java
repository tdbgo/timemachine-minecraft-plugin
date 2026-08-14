package dev.playcity.timemachine.i18n;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record LocalizedMessage(String key, List<Object> arguments) {
    public LocalizedMessage {
        Objects.requireNonNull(key, "key");
        arguments = List.copyOf(arguments);
    }

    public static LocalizedMessage of(String key, Object... arguments) {
        return new LocalizedMessage(key, Arrays.asList(arguments.clone()));
    }

    public Object[] argumentsArray() {
        return arguments.toArray();
    }
}
