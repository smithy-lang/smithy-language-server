$version: "2"

namespace b

string Ignored

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
