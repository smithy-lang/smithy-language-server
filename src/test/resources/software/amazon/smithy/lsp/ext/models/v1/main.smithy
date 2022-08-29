$version: "1.0"

namespace com.foo

structure SingleLine {}

        structure MultiLine {
            a: String,


            b: String,
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
    input: MyOperationInput,
    output: MyOperationOutput,
    errors: [MyError]
}

structure MyOperationInput {
    foo: String,
    @required
    myId: MyId
}

structure MyOperationOutput {
    corge: String,
    qux: String
}

@error("client")
structure MyError {
    blah: String,
    blahhhh: Integer
}

resource MyResource {
    identifiers: { myId: MyId },
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

@trait
structure emptyTraitStruct {}
