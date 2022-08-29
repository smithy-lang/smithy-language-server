$version: "2.0"

$operationInputSuffix: "ClutteredInput"
$operationOutputSuffix: "ClutteredOutput"


$extraneous: "extraneous"


// Comments in preamble
// Whitespace



namespace com.clutter


// Use statements
use com.example#OtherStructure



use com.extras#Extra

/// With doc comment
structure StructureWithDependencies {
    extra: Extra
    example: OtherStructure
}

structure StructureWithNoDependencies {
    member: String
}