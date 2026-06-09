package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

public record PlayerPage(List<PlayerDto> data, int page, int pageSize, int total) {}
