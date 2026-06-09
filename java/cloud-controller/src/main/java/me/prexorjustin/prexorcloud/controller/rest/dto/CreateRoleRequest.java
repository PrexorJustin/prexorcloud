package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

public record CreateRoleRequest(String name, List<String> permissions) {}
