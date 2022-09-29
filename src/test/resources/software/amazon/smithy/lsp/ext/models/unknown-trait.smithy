$version: "2.0"

namespace com.foo

use com.external#unknownTrait

@unknownTrait
structure Foo {}

structure Bar {
    member: Foo
}

structure Baz {
    member: Bar
}
