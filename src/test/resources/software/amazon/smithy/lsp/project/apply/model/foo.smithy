$version: "2.0"
namespace com.foo

use com.bar#HasMyBool

apply MyOpInput @tags(["foo"])

apply MyStruct$member @tags(["bar"])

structure MyOpInput with [HasMyBool] {
    @required
    body: String
}

apply MyOpInput$myBool @documentation("docs")

structure MyStruct {
    member: String
}

apply MyStruct @documentation("more docs")

apply MyOpInput$body @documentation("even more docs")

apply HasMyBool @tags(["baz"])