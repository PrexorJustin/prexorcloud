package me.prexorjustin.prexorcloud.modules.stats.rest.dto;

import java.util.List;

import me.prexorjustin.prexorcloud.modules.stats.data.PlayerStat;

public record TopPlayersResponse(int count, List<PlayerStat> players) {}
