package org.specs2
package data

import mutable.Specification
import matcher.InternalScalazMatchers
import internal.scalaz._
import Monoidx._

class MonoidxSpec extends Specification with InternalScalazMatchers {
  "There is a Monoid for a Map if there is a Monoid for the values" >>
    implicitly[Monoid[Map[String, Int]]].isMonoid
}
