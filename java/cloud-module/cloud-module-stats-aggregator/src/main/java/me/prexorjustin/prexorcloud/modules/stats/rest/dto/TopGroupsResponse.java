package me.prexorjustin.prexorcloud.modules.stats.rest.dto;

import java.util.List;

import me.prexorjustin.prexorcloud.modules.stats.data.GroupStat;

public record TopGroupsResponse(int count, List<GroupStat> groups) {}
