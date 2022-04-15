$version: "1.0"

namespace example.foo

structure SingleLine {}

structure MultiLine {
    a: String,
    b: String,
    c: String
}

@pattern("^[A-Za-z0-9 ]+$")
string SingleTrait

@input
@error("client")
structure MultiTrait {
    a: String
}

// Line comments
// comments
@input
// comments
@error("client")
@references(
    [
        {
            resource: City
        }
    ]
)
structure MultiTraitAndLineComments {
    a: String
}

/// Doc comments
/// comments
@input
@error("client")
structure MultiTraitAndDocComments {
    a: String
}
