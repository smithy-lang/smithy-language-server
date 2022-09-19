$version: "2.0"

namespace com.applywithmixin

use com.mixinimports#HasIsTestParam

structure SomeOpInput with [HasIsTestParam] {
    @required
    body: String
}

// An apply statement that targets a mixed in member from another namespace
apply SomeOpInput$isTest @documentation("Some documentation")
