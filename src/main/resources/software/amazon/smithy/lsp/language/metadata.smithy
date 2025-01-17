$version: "2.0"

namespace smithy.lang.server

structure BuiltinMetadata {
    /// Suppressions are used to suppress specific validation events.
    /// See [Suppressions](https://smithy.io/2.0/spec/model-validation.html#suppressions)
    suppressions: Suppressions

    /// An array of validator objects used to constrain the model.
    /// See [Validators](https://smithy.io/2.0/spec/model-validation.html#validators)
    validators: Validators

    /// An array of severity override objects used to raise the severity of non-suppressed validation events.
    /// See [Severity overrides](https://smithy.io/2.0/spec/model-validation.html#severity-overrides)
    severityOverrides: SeverityOverrides
}

list Suppressions {
    member: Suppression
}

list Validators {
    member: Validator
}

list SeverityOverrides {
    member: SeverityOverride
}

structure Suppression {
    /// The hierarchical validation event ID to suppress.
    id: String

    /// The validation event is only suppressed if it matches the supplied namespace.
    /// A value of * can be provided to match any namespace.
    /// * is useful for suppressing validation events that are not bound to any specific shape.
    namespace: AnyNamespace

    /// Provides an optional reason for the suppression.
    reason: String
}

structure Validator {
    name: ValidatorName
    id: String
    message: String
    severity: ValidatorSeverity
    namespaces: AnyNamespaces
    selector: String
    configuration: ValidatorConfig
}

enum ValidatorSeverity {
    NOTE = "NOTE"
    WARNING = "WARNING"
    DANGER = "DANGER"
}

list AnyNamespaces {
    member: AnyNamespace
}

structure SeverityOverride {
    id: String
    namespace: AnyNamespace
    severity: SeverityOverrideSeverity
}

enum SeverityOverrideSeverity {
    WARNING = "WARNING"
    DANGER = "DANGER"
}

structure BuiltinValidators {
    EmitEachSelector: EmitEachSelectorConfig
    EmitNoneSelector: EmitNoneSelectorConfig
    UnreferencedShapes: UnreferencedShapesConfig
}

structure EmitEachSelectorConfig {
    @required
    selector: Selector
    bindToTrait: AnyTrait
    messageTemplate: String
}

structure EmitNoneSelectorConfig {
    @required
    selector: Selector
}

structure UnreferencedShapesConfig {
    selector: Selector = "service"
}
