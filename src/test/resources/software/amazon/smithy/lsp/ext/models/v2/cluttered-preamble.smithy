$version: "2.0"

$operationInputSuffix: "In"
$operationOutputSuffix: "Out"


$extraneous: "extraneous"


// Comments in preamble
// Whitespace



namespace com.clutter


// Use statements
use com.example#OtherStructure



use com.extras#Extra

/// With doc comment
@mixin
structure StructureWithDependencies {
    extra: Extra
    example: OtherStructure
}

operation ClutteredInlineOperation {
    input := with [StructureWithDependencies] {
    }
    output := with [StructureWithDependencies] {
        additional: Integer
    }
}

