package com.activepieces.actions.model.action;

import com.activepieces.actions.model.action.settings.ComponentSettingsView;
import com.activepieces.common.code.ArtifactMetadata;
import com.activepieces.common.code.ArtifactMetadataSettings;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.apache.commons.io.FilenameUtils;
import org.springframework.data.annotation.Transient;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class ComponentActionMetadataView extends ActionMetadataView  implements ArtifactMetadata {

    @JsonProperty
    @NotNull
    @Valid
    private ComponentSettingsView settings;

    @Override
    @Transient
    public String getSourcePath(UUID flowVersionId) {
        return flowVersionId + "/" + getName() + "/source.zip";
    }

    @Override
    @Transient
    public String getPackagePath(UUID flowVersionId) {
        return flowVersionId + "/" + getName() + "/" + FilenameUtils.removeExtension(settings.getArtifact()) + ".js";
    }

    @Override
    public ArtifactMetadataSettings getArtifactSettings() {
        return settings;
    }
}
