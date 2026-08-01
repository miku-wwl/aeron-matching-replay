package io.github.mikuwwl.matchingreplay.aeron;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Arguments
{
    private final Map<String, String> values;

    private Arguments(final Map<String, String> values)
    {
        this.values = values;
    }

    public static Arguments parse(final String[] args)
    {
        final Map<String, String> values = new LinkedHashMap<>();
        for (final String argument : args)
        {
            if (!argument.startsWith("--") || !argument.contains("="))
            {
                throw new IllegalArgumentException("Expected --name=value, got: " + argument);
            }
            final int separator = argument.indexOf('=');
            final String name = argument.substring(2, separator);
            final String value = argument.substring(separator + 1);
            if (name.isBlank() || value.isBlank() || values.put(name, value) != null)
            {
                throw new IllegalArgumentException("Invalid or duplicate argument: " + argument);
            }
        }
        return new Arguments(Map.copyOf(values));
    }

    public String stringValue(final String name, final String defaultValue)
    {
        return values.getOrDefault(name, defaultValue);
    }

    public int intValue(final String name, final int defaultValue)
    {
        return Integer.parseInt(values.getOrDefault(name, Integer.toString(defaultValue)));
    }

    public long longValue(final String name, final long defaultValue)
    {
        return Long.parseLong(values.getOrDefault(name, Long.toString(defaultValue)));
    }

    public boolean booleanValue(final String name, final boolean defaultValue)
    {
        return Boolean.parseBoolean(values.getOrDefault(name, Boolean.toString(defaultValue)));
    }
}
