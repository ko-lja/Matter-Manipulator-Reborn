package lu.kolja.matter_manipulator.common.integration;

import lu.kolja.matter_manipulator.api.CableIntegration;

import lu.kolja.matter_manipulator.common.state.CapturedBlock;

public record CablePreparation(CapturedBlock captured, CableIntegration.ApplyTiming applyTiming) {

    public boolean hasIntegrationData() {
        return captured != null && captured.hasCompat();
    }
}
