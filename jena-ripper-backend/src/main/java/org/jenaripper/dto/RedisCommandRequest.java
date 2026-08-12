package org.jenaripper.dto;

import java.util.List;

public record RedisCommandRequest(String command, List<String> args) {}
