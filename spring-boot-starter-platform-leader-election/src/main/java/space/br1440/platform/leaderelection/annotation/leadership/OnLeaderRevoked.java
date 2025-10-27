package space.br1440.platform.leaderelection.annotation.leadership;

import java.lang.annotation.*;

/**
 * Аннотация для методов, которые должны выполняться при потере лидерства.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnLeaderRevoked {

}
