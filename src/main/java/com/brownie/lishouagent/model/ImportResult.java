package com.brownie.lishouagent.model;

/** importedCount is the number of chunks written in this explicit import. */
public record ImportResult(int documentCount, int chunkCount, int importedCount) {}
