$version: "2.0"
$operationInputSuffix: "FooInput"
$operationOutputSuffix: "BarOutput"

namespace com.foo

structure SingleLine {}

        structure MultiLine {
            a: String


            b: String
            @required
            c: SingleLine
        }

    @pattern("^[A-Za-z0-9 ]+$")
    string SingleTrait

@input
@tags(["foo"])
structure MultiTrait {
    a: String}

// Line comments
// comments
  @input
    // comments
    @tags(["a",
        "b",
        "c",
        "d",
        "e",
        "f"
    ]
)
structure MultiTraitAndLineComments {
    a: String
}




/// Doc comments
/// Comment about corresponding MultiTrait shape
@input
@tags(["foo"])
structure MultiTraitAndDocComments {
    a: String
}

@readonly
operation MyOperation {
    input: MyOperationInput
    output: MyOperationOutput
    errors: [MyError]
}

structure MyOperationInput {
    foo: String
    @required
    myId: MyId
}

structure MyOperationOutput {
    corge: String
    qux: String
}

@error("client")
structure MyError {
    blah: String
    blahhhh: Integer
}

resource MyResource {
    identifiers: { myId: MyId }
    read: MyOperation
}

string MyId

string InputString

apply MyOperation @http(method: "PUT", uri: "/bar", code: 200)

     @http(method: "PUT", uri: "/foo", code: 200)
     @documentation("doc has parens ()")
     @tags(["foo)",
            "bar)",
            "baz)"])
     @examples([{
         title: "An)Operation"
     }])
  operation AnOperation {}

structure StructWithDefaultSugar {
    foo: String = "bar"
}

operation MyInlineOperation {
    input := {
        foo: String
        bar: String
    }
    output := {
        baz: String
    }
}

@mixin
structure UserIds {
    @required
    email: String

    @required
    id: String
}

@mixin
structure UserDetails {
    status: String
}

operation GetUser {
    input := with [UserIds, UserDetails] {
        optional: String
    }
    output := with [UserIds, UserDetails] {
        description: String
    }
}

structure ElidedUserInfo with [UserIds, UserDetails]{
    @tags(["foo", "bar"])
    $email

    @tags(["baz"])
    $status
}

operation ElidedGetUser {
    input := with [UserIds, UserDetails] {

       @tags(["hello"])
       $id
       optional: String
    }
    output := with [UserIds, UserDetails] {
        @tags(["goodbye"])
        $status

        description: String
    }
}

enum Suit {
    DIAMOND = "diamond"
    CLUB = "club"
    HEART = "heart"
    SPADE = "spade"
}

operation MyInlineOperationReversed {
    output := {
        baz: String
    }
    input := {
        foo: String
    }
}

// The input and output match the name conventions for inline inputs and outputs,
// but are not actually inlined.
operation FalseInlined {
    input: FalseInlinedFooInput
    output: FalseInlinedBarOutput
}

structure FalseInlinedFooInput {
    a: String
}

structure FalseInlinedBarOutput {
    b: String
}

operation FalseInlinedReversed {
    output: FalseInlinedReversedBarOutput
    input: FalseInlinedReversedFooInput
}

structure FalseInlinedReversedFooInput {
    c: String
}

structure FalseInlinedReversedBarOutput {
    d: String
}

@trait
structure emptyTraitStruct {}

operation ShortInputOutput {
    output: ShortO
    input: ShortI
}

@input
structure ShortI {
    c: String
}

@output
structure ShortO {
    d: String
}