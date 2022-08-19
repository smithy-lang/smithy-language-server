$version: "2.0"
$operationInputSuffix: "Request"
$operationOutputSuffix: "Response"

namespace ns.preamble

use ns.foo#FooTrait

use ns.bar#BarTrait

@FooTrait
@BarTrait
string MyString
