package space.br1440.platform.tracing.perftests.sut;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SUT (system under test) для macro перф-прогонов Фазы 17 (PR-3/PR-4).
 * <p>
 * Production-like Spring Boot сервис: platform starter (servlet) + REST-эндпоинты,
 * генерирующие репрезентативную span-нагрузку (атрибуты HTTP-server запроса, вложенный
 * internal span через фасад, ошибки, медленные ответы). Приложение ИДЕНТИЧНО во всех
 * конфигурациях матрицы M0–M10 — отличается только окружение JVM
 * (наличие -javaagent, extension, ratio, доступность Collector'а).
 */
@SpringBootApplication
public class SutApplication {

    public static void main(String[] args) {
        SpringApplication.run(SutApplication.class, args);
    }
}
