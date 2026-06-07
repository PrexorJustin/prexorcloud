package me.prexorjustin.prexorcloud.controller.group;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GroupConfigResourceFieldsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsCpuAndDiskReservationsFromConfig() throws Exception {
        GroupConfig group = mapper.readValue("""
                {
                  "name": "lobby",
                  "memoryMb": 2048,
                  "cpuReservation": 0.25,
                  "diskReservationMb": 4096
                }
                """, GroupConfig.class);

        assertEquals(2048, group.memoryMb());
        assertEquals(0.25, group.cpuReservation());
        assertEquals(4096, group.diskReservationMb());
    }

    @Test
    void normalizesNegativeCpuAndDiskReservationsToNoReservation() throws Exception {
        GroupConfig group = mapper.readValue("""
                {
                  "name": "lobby",
                  "cpuReservation": -1.0,
                  "diskReservationMb": -128
                }
                """, GroupConfig.class);

        assertEquals(0.0, group.cpuReservation());
        assertEquals(0, group.diskReservationMb());
    }
}
