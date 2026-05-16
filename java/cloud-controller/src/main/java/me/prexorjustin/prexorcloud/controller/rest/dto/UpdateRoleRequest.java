package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

public record UpdateRoleRequest(List<String> permissions) {}
