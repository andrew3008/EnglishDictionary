package space.br1440.platform.uid1440;

import java.util.Objects;
import java.util.regex.Pattern;

public final class Uid1440 {

    private static final String DELIMITER = "-";

    private static final Pattern ENTITY_PATTERN = Pattern.compile("^[A-Z0-9_]+$");
    private static final Pattern UUID32_HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    private Uid1440(){}


    public static String generateUID(Domain domain, String entity) {
        Objects.requireNonNull(domain, "domain must not be null");
        requireValidEntity(entity);
        String uuidHex = UuidV7.generateHexNoDashes();
        return domain.name() + DELIMITER + entity + DELIMITER + uuidHex;
    }

    public static boolean validateUID(String uid) {
        try {
            parseAndValidate(uid);
            return true;
        } catch ( IllegalArgumentException ex) {
            return false;
        }
    }


    public static void parseAndValidate(String uid){
        if (uid == null){
            throw new Uid1440Exception("UID is null");
        }
        String trimmed = uid.trim();
        if (trimmed.isEmpty()) {
            throw new Uid1440Exception("UID is blank");
        }
        
        String[] parts = trimmed.split(String.valueOf(DELIMITER), -1);
        if (parts.length !=3){
            throw new Uid1440Exception("UID must have exactly 3 parts separated by '-' (domain-entity-uuid)");
        }

        String domainPart = parts[0];
        String entityPart = parts[1];
        String uuidHexPart = parts[2];

        requireValidDomain(domainPart);
        requireValidEntity(entityPart);
        requireValidUuidHex(uuidHexPart);
        requireUuidV7(uuidHexPart);
    }


    private static void requireValidDomain(String domainPart) {
        if(domainPart == null || domainPart.isEmpty()) {
            throw new Uid1440Exception("Domain part is empty");
        }
        try {
            Domain.valueOf(domainPart);
        } catch (IllegalArgumentException ex) {
            throw new Uid1440Exception("Unknown domain: " + domainPart, ex);
        }
    }

    private static void requireValidEntity(String entity) {
        if (entity == null) {
            throw new Uid1440Exception("Entity is null");
        }
        if (entity.isEmpty()) {
            throw new Uid1440Exception("Entity is empty");
        }
        if (!ENTITY_PATTERN.matcher(entity).matches()){
            throw new Uid1440Exception("Invalid entity '" + entity + "'. Allowed uppercase letters A-Z, digits 0-9, underscore '_'");
        }
    }

    private static void requireValidUuidHex(String uuidHex) {
        if (uuidHex == null) {
            throw new Uid1440Exception("UUID part is null");
        }
        if (!UUID32_HEX_PATTERN.matcher(uuidHex).matches()) {
            throw new Uid1440Exception("UUID part must be hex (0-9, a-f) and contain no dashes");
        }
    }

   private static void requireUuidV7(String uuidHex) {
       if (!UuidV7.isValidUuidV7Hex(uuidHex)) {
           throw new Uid1440Exception("UUID part is not a valid RFC4122 UUIDv7");
       }
   }

}
