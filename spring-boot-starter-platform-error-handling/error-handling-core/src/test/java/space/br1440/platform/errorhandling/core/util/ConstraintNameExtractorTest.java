package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintNameExtractorTest {

    @Nested
    @DisplayName("null/empty input")
    class NullAndEmpty {

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("returns null for null/empty message")
        void returnsNullForNullOrEmpty(String message) {
            // when / then
            assertThat(ConstraintNameExtractor.extract(message)).isNull();
        }

        @Test
        @DisplayName("returns null for message without constraint name")
        void returnsNullWhenNoMatch() {
            // when / then
            assertThat(ConstraintNameExtractor.extract("ERROR: some general database error")).isNull();
        }
    }

    @Nested
    @DisplayName("PostgreSQL patterns")
    class Postgres {

        @ParameterizedTest
        @CsvSource({
                "constraint \"users_email_key\",                            users_email_key",
                "CONSTRAINT \"pk_orders\",                                  pk_orders",
                "foreign key constraint \"fk_order_user_id\",               fk_order_user_id",
                "unique constraint \"uq_user_email\",                       uq_user_email",
                "constraint 'accounts_pkey',                                accounts_pkey",
        })
        @DisplayName("extracts constraint from PostgreSQL error messages")
        void extractsPostgresConstraint(String message, String expected) {
            // when / then
            assertThat(ConstraintNameExtractor.extract(message)).isEqualTo(expected);
        }

        @Test
        @DisplayName("handles quoted constraint with schema prefix")
        void handlesSchemaQualifiedConstraint() {
            // given
            String msg = "ERROR: duplicate key value violates unique constraint \"public.users_email_key\"";

            // when / then
            assertThat(ConstraintNameExtractor.extract(msg)).isEqualTo("public.users_email_key");
        }
    }

    @Nested
    @DisplayName("MySQL patterns")
    class MySQL {

        @ParameterizedTest
        @CsvSource({
                "Duplicate entry '123' for key 'users.PRIMARY',             PRIMARY",
                "Duplicate entry 'test@email' for key 'idx_unique_email',   idx_unique_email",
                "Cannot delete or update a parent row: for key 'fk_order',  fk_order",
        })
        @DisplayName("extracts constraint from MySQL error messages")
        void extractsMySqlConstraint(String message, String expected) {
            // when / then
            assertThat(ConstraintNameExtractor.extract(message)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("H2 patterns")
    class H2 {

        @ParameterizedTest
        @CsvSource({
                "Unique index or primary key violation: \"CONSTRAINT_IDX_1\",   CONSTRAINT_IDX_1",
                "Referential integrity violation: \"FK_USER_ORDER\",            FK_USER_ORDER",
        })
        @DisplayName("extracts constraint from H2 error messages")
        void extractsH2Constraint(String message, String expected) {
            // when / then
            assertThat(ConstraintNameExtractor.extract(message)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("priority: PostgreSQL pattern matches first")
    class PatternPriority {

        @Test
        @DisplayName("PostgreSQL pattern takes precedence over H2 when both could match")
        void postgresPatternWins() {
            // given
            String msg = "constraint \"my_constraint\" violation: \"other_constraint\"";

            // when / then
            assertThat(ConstraintNameExtractor.extract(msg)).isEqualTo("my_constraint");
        }
    }
}
