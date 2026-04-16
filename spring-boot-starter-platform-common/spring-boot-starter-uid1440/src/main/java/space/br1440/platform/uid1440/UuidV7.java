package space.br1440.platform.uid1440;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.Locale;
import java.util.UUID;

final class UuidV7 {

    private UuidV7() {}

    static String generateHexNoDashes() {
        UUID uuid = UuidCreator.getTimeOrderedEpoch();
        return uuid.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }


    static boolean isValidUuidV7Hex(String uuid32Hex) {
        if (uuid32Hex == null || uuid32Hex.length() != 32) {
            return false;
        }

        String dashes =
                uuid32Hex.substring(0, 8) + "-" +
                        uuid32Hex.substring(8, 12) + "-" +
                        uuid32Hex.substring(12, 16) + "-" +
                        uuid32Hex.substring(16, 20) + "-" +
                        uuid32Hex.substring(20);

        final UUID uuid;

        try{
            uuid = UUID.fromString(dashes);
        } catch (IllegalArgumentException ex){
            return false;
        }

        return uuid.version() == 7 && uuid.variant() == 2;
    }
}
