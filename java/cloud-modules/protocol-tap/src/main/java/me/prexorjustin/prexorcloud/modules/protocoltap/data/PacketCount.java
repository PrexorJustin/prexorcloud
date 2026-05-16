package me.prexorjustin.prexorcloud.modules.protocoltap.data;

/** One bucketed packet observation: which group, which packet, how many. */
public record PacketCount(String group, String packetType, long count, long observedAt) {}
