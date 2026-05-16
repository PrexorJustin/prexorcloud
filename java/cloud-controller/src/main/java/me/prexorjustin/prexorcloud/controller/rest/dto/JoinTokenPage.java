package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

public record JoinTokenPage(List<JoinTokenDto> data, int page, int pageSize, int total) {}
