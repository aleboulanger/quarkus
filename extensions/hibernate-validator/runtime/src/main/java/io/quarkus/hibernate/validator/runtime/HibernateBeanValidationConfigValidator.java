package io.quarkus.hibernate.validator.runtime;

import jakarta.validation.Validator;

import io.smallrye.config.validator.BeanValidationConfigValidator;

public class HibernateBeanValidationConfigValidator implements BeanValidationConfigValidator {
    @Override
    public Validator getValidator() {
        return ValidatorHolder.getValidator();
    }
}
