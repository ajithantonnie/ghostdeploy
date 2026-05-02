package com.ghostdeploy.alerting;

import com.ghostdeploy.model.Alert;

public interface AlertHandler {
    void handle(Alert alert);
}
