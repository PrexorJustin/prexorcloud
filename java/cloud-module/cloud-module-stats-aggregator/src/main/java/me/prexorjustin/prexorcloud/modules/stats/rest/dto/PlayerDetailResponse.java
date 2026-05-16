package me.prexorjustin.prexorcloud.modules.stats.rest.dto;

import java.util.List;

import me.prexorjustin.prexorcloud.api.domain.PlayerJourneyEntry;
import me.prexorjustin.prexorcloud.modules.stats.data.PlayerStat;
import me.prexorjustin.prexorcloud.modules.stats.data.SessionRecord;

public record PlayerDetailResponse(
        PlayerStat stat, List<SessionRecord> recentSessions, List<PlayerJourneyEntry> recentJourney) {}
