package com.stockguard.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreateReservationCommandTest {

    private static final UUID PRODUCT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void fingerprintIsStableAndHexEncodedSha256() {
        String fingerprint = new CreateReservationCommand(PRODUCT, 2, 60, "key").fingerprint();

        assertThat(fingerprint).hasSize(64).matches("[0-9a-f]+");
        assertThat(new CreateReservationCommand(PRODUCT, 2, 60, "key").fingerprint()).isEqualTo(fingerprint);
    }

    @Test
    @DisplayName("Anything that changes what is requested changes the fingerprint")
    void fingerprintDiffersWhenRequestDiffers() {
        String base = new CreateReservationCommand(PRODUCT, 2, 60, "key").fingerprint();

        assertThat(new CreateReservationCommand(UUID.randomUUID(), 2, 60, "key").fingerprint()).isNotEqualTo(base);
        assertThat(new CreateReservationCommand(PRODUCT, 3, 60, "key").fingerprint()).isNotEqualTo(base);
        assertThat(new CreateReservationCommand(PRODUCT, 2, 61, "key").fingerprint()).isNotEqualTo(base);
    }

    @Test
    @DisplayName("Omitting the TTL is a different request from asking for a specific TTL")
    void missingTtlIsDistinct() {
        assertThat(new CreateReservationCommand(PRODUCT, 2, null, "key").fingerprint())
                .isNotEqualTo(new CreateReservationCommand(PRODUCT, 2, 900, "key").fingerprint());
    }

    @Test
    @DisplayName("The key is what is being compared against, so it is not part of the fingerprint")
    void keyIsNotPartOfFingerprint() {
        assertThat(new CreateReservationCommand(PRODUCT, 2, 60, "first").fingerprint())
                .isEqualTo(new CreateReservationCommand(PRODUCT, 2, 60, "second").fingerprint());
    }
}
