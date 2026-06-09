package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

public record RoleDto(String name, List<String> permissions, boolean builtIn, long userCount) {}
