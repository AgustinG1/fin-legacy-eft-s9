package cl.duoc.eft.batch;

public record SourceRow(String dataset, Kind kind, String source, int line, String raw) { }
