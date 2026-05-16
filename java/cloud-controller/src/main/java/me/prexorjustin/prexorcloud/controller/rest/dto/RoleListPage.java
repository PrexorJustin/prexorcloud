package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

public record RoleListPage(List<RoleDto> data, int page, int pageSize, int total) {}
