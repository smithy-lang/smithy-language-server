$version: "2.0"

namespace com.main

use com.imports#HasIsTestParam

// Apply before shape definition
apply SomeOpInput @tags(["someTag"])

// Apply as first line in shapes section
apply ArbitraryStructure$member @tags(["someTag"])

structure SomeOpInput with [HasIsTestParam] {
    @required
    body: String
}

/// Arbitrary doc comment

// Arbitrary comment

// Apply targeting a mixed in member from another namespace
apply SomeOpInput$isTest @documentation("Some documentation")

// Structure to break up applys
structure ArbitraryStructure {
    member: String
}

// Multiple applys before first shape definition
apply ArbitraryStructure @documentation("Some documentation")

// Apply targeting non-mixed in member
apply SomeOpInput$body @documentation("Some documentation")


// Apply targeting a shape from another namespace
apply HasIsTestParam @documentation("Some documentation")
