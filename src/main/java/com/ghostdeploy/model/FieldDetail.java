package com.ghostdeploy.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldDetail {
    private String fieldName;
    private String type; // STRING, NUMBER, BOOLEAN, NULL, ARRAY, OBJECT
    private String value; // For enums or tracking values. Can be null for large strings.
    private boolean isNull;
}
