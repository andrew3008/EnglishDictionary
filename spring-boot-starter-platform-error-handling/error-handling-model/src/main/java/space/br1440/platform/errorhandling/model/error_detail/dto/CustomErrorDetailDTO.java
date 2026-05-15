package space.br1440.platform.errorhandling.model.error_detail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

@Value
@EqualsAndHashCode
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"detailType", "metadata"})
public class CustomErrorDetailDTO implements ErrorDetailDTO {

    @NotNull
    String detailType;

    @Builder.Default
    Map<String, String> metadata = Map.of();
}
