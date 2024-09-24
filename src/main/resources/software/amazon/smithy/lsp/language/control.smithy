$version: "2.0"

namespace smithy.lang.server

structure BuiltinControl {
    /// Defines the [version](https://smithy.io/2.0/spec/idl.html#smithy-version)
    /// of the smithy idl used in this model file.
    version: SmithyIdlVersion = "2.0"

    /// Defines the suffix used when generating names for
    /// [inline operation input](https://smithy.io/2.0/spec/idl.html#idl-inline-input-output).
    operationInputSuffix: String = "Input"

    /// Defines the suffix used when generating names for
    /// [inline operation output](https://smithy.io/2.0/spec/idl.html#idl-inline-input-output).
    operationOutputSuffix: String = "Output"
}
