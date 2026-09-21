package org.jenaripper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenaripper.dto.CimClassReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CimClassReferenceTest {
    @Test
    void loadsReadOnlyDictionaryByFullClassUriAndAllowsUnknownClasses() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream input = new ClassPathResource("cim-class-reference.json").getInputStream()) {
            Map<String, CimClassReference> references = mapper.readValue(input,
                    new TypeReference<Map<String, CimClassReference>>() {});
            assertThat(references).hasSize(114);
            assertThat(references.keySet()).allMatch(uri -> uri.startsWith("http://"));
            assertThat(references.values()).allSatisfy(reference -> {
                assertThat(reference.titleRu()).isNotBlank();
                assertThat(reference.description()).isNotBlank();
                String text = (reference.description() + " " + (reference.purpose() == null ? "" : reference.purpose()))
                        .toLowerCase();
                assertThat(text).doesNotContain("в используемой метамодели", "в данном проекте", "jena ripper",
                        "данный класс предназначен", "в рамках системы");
            });
            CimClassReference plant = references.get("http://iec.ch/TC57/CIM100#Plant");
            assertThat(plant.titleRu()).isEqualTo("Электростанция");
            assertThat(plant.description()).isNotBlank();
            assertThat(references.get("http://iec.ch/TC57/CIM100#Substation").purpose()).isNotBlank();
            assertThat(references.get("http://example.org/Unknown")).isNull();
            assertThat(references.get("http://iec.ch/TC57/CIM100#SynchronousMachine").description()).isNotBlank();
            assertThat(mapper.readTree(mapper.writeValueAsString(plant)).path("titleRu").asText())
                    .isEqualTo("Электростанция");
        }
    }
}
