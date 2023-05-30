$version: "2"

namespace a

operation HelloWorld {
    input := {
        @required
        name: String
    }
    output := {
        @required
        name: String
    }
}
