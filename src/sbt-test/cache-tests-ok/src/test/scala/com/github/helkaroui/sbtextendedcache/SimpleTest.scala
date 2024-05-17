package org.engie.mad.calculator

import org.scalatest.Matchers
import org.scalatest.FlatSpec

class SimpleTest extends FlatSpec with Matchers {

  "SimpleTest" should "success the test" in {
    assert("3.1.1" == "3.1.1", "The spark version is not correct")
  }

  it should "fail this test" in {
    assert(true, "This test should fail")
  }

}
