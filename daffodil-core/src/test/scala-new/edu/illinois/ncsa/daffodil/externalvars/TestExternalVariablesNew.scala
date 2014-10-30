package edu.illinois.ncsa.daffodil.externalvars

/* Copyright (c) 2012-2013 Tresys Technology, LLC. All rights reserved.
 *
 * Developed by: Tresys Technology, LLC
 *               http://www.tresys.com
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal with
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 * 
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimers.
 * 
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimers in the
 *     documentation and/or other materials provided with the distribution.
 * 
 *  3. Neither the names of Tresys Technology, nor the names of its contributors
 *     may be used to endorse or promote products derived from this Software
 *     without specific prior written permission.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
 * SOFTWARE.
 */

import edu.illinois.ncsa.daffodil.xml.XMLUtils
import edu.illinois.ncsa.daffodil.util._
import scala.xml._
import edu.illinois.ncsa.daffodil.compiler._
import edu.illinois.ncsa.daffodil.schema.annotation.props.gen._
import edu.illinois.ncsa.daffodil.schema.annotation.props._
import junit.framework.Assert._
import org.junit.Test
import edu.illinois.ncsa.daffodil.dsom.SchemaSet
import edu.illinois.ncsa.daffodil.xml.NS
import edu.illinois.ncsa.daffodil.Implicits._
import org.junit.Test
import edu.illinois.ncsa.daffodil.xml.NoNamespace

class TestExternalVariablesNew extends Logging {
  val xsd = XMLUtils.XSD_NAMESPACE
  val dfdl = XMLUtils.DFDL_NAMESPACE
  val xsi = XMLUtils.XSI_NAMESPACE
  val example = XMLUtils.EXAMPLE_NAMESPACE

  val dummyGroupRef = null // just because otherwise we have to construct too many things.

  def generateSD(topLevelAnnotations: Seq[Node] = <dfdl:format ref="tns:daffodilTest1"/>) = {
    lazy val sch = SchemaUtils.dfdlTestSchema(
      topLevelAnnotations,
      <xs:element name="fake" type="xs:string" dfdl:lengthKind="delimited"/>
      <xs:element name="fake2" type="tns:fakeCT"/>
      <xs:complexType name="fakeCT">
        <xs:sequence>
          <xs:group ref="tns:fakeGroup"/>
          <xs:element ref="tns:fake"/>
        </xs:sequence>
      </xs:complexType>
      <xs:group name="fakeGroup">
        <xs:choice>
          <xs:sequence/>
        </xs:choice>
      </xs:group>)
    lazy val xsd_sset = new SchemaSet(sch, "http://example.com", "fake")
    lazy val xsd_schema = xsd_sset.getSchema(NS("http://example.com")).get
    lazy val fakeSD = xsd_schema.schemaDocuments(0)
    (fakeSD, xsd_sset)
  }

  def FindValue(collection: Map[String, String], key: String, value: String): Boolean = {
    val found: Boolean = Option(collection.find(x => x._1 == key && x._2 == value)) match {
      case Some(_) => true
      case None => false
    }
    found
  }

  @Test def testQNameForIndividualVars() = {
    // This test just verifies that we're getting back
    // the right values for the namespace and variable name.
    //
    // This is required functionality for when individual vars
    // are passed in via the CLI using the -D option.
    //
    val varWithNS = "{someNS}varWithNS"
    val varNoNS = "{}varNoNS"
    val varGuessNS = "varGuessNS"

    val (varWithNS_NS, varWithNS_localName) = XMLUtils.getQName(varWithNS)
    val (varNoNS_NS, varNoNS_localName) = XMLUtils.getQName(varNoNS)
    val (varGuessNS_NS, varGuessNS_localName) = XMLUtils.getQName(varGuessNS)

    assertTrue(varWithNS_NS.isDefined)
    assertEquals(varWithNS_NS.get, NS("someNS"))
    assertEquals("varWithNS", varWithNS_localName)

    assertTrue(varNoNS_NS.isDefined)
    assertEquals(varNoNS_NS.get, NoNamespace)
    assertEquals("varNoNS", varNoNS_localName)

    assertFalse(varGuessNS_NS.isDefined)
    assertEquals("varGuessNS", varGuessNS_localName)
  }

}
