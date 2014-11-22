package edu.illinois.ncsa.daffodil.xml

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

/**
 * Adapted from a question/answer on the Stack Overflow web site.
 *
 * See http://stackoverflow.com/questions/4446137/how-to-track-the-source-line-location-of-an-xml-element
 */

import java.io.File
import java.net.URI
import java.net.URL
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.xml.Attribute
import scala.xml.Elem
import scala.xml.InputSource
import scala.xml.MetaData
import scala.xml.NamespaceBinding
import scala.xml.Node
import scala.xml.Null
import scala.xml.SAXParseException
import scala.xml.SAXParser
import scala.xml.Text
import scala.xml.TopScope
import scala.xml.parsing.NoBindingFactoryAdapter
import org.apache.xerces.dom.DOMInputImpl
import org.apache.xerces.xni.parser.XMLInputSource
import org.apache.xml.resolver.Catalog
import org.apache.xml.resolver.CatalogManager
import org.w3c.dom.ls.LSInput
import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.util.LogLevel
import edu.illinois.ncsa.daffodil.util.Logging
import edu.illinois.ncsa.daffodil.util.Misc
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import javax.xml.validation.SchemaFactory
import java.io.InputStream
import java.io.BufferedInputStream
import org.w3c.dom.ls.LSResourceResolver
import java.io.Reader
import java.io.FileInputStream
import org.xml.sax.ext.DefaultHandler2
import javax.xml.transform.sax.SAXSource

/**
 * Resolves URI/URL/URNs to loadable files/streams.
 *
 * Uses xml catalogs.
 *
 * The user can specify their own catalogs by putting
 * CatalogManager.properties on the classpath when they run
 * daffodil.
 *
 * You can also turn on/off verbose messaging from the resolver
 * by putting 'verbosity=4' in that same file.
 *
 * In all cases, we get the resource daffodil-built-in-catalog.xml
 * and that gets priority, so that entities we choose to resolve
 * as built-ins are resolved from the Daffodil jars.
 */
class DFDLCatalogResolver private ()
  extends org.apache.xerces.xni.parser.XMLEntityResolver
  with org.w3c.dom.ls.LSResourceResolver
  with org.xml.sax.EntityResolver
  with org.xml.sax.ext.EntityResolver2
  with Logging {

  lazy val init = {
    cm
    catalogFiles
    delegate
  }

  lazy val catalogFiles = cm.getCatalogFiles().asScala.toList.asInstanceOf[List[String]]
  // Caution: it took a long time to figure out how to use
  // the XML Catalog stuff. Many permutations were attempted
  // so change this next block of code at your peril
  //
  lazy val cm = {
    val cm = new CatalogManager()
    cm.setIgnoreMissingProperties(true)
    cm.setRelativeCatalogs(true)
    //
    // Note: don't edit code to turn this on and off. You can 
    // create CatalogManager.properties file (don't commit/push it tho)
    // and put one line in it 'verbosity=4' and it will do the same
    // as this next line.
    // cm.setVerbosity(4)
    //
    // The user might specify catalogs using the CatalogManager.properties
    // file. So to insure that our catalog "wins" any conflicts with the user's 
    // catalogs, we take any user-specified catalogs and explicitly put 
    // our catalog first in the catalog list, and then set it again.
    //
    val catFiles = cm.getCatalogFiles().toArray.toList.asInstanceOf[List[String]]
    log(LogLevel.Debug, "initial catalog files: %s ", catFiles)
    val builtInCatalog = Misc.getRequiredResource("/daffodil-built-in-catalog.xml")
    val newCatFiles = builtInCatalog.toString() :: catFiles
    cm.setCatalogFiles(newCatFiles.mkString(";"))

    val catFilesAfter = cm.getCatalogFiles()
    log(LogLevel.Debug, "final catalog files: %s ", catFilesAfter)
    cm
  }

  lazy val delegate = {
    val delegate = new Catalog(cm)
    //    delegate.getCatalogManager().debug.setDebug(100) // uncomment for even more debug output
    delegate.setupReaders()
    delegate.loadSystemCatalogs()
    delegate
  }

  /**
   * Flag to let us know we're already inside the resolution of the
   * XML Schema URI. See comment below.
   *
   * This is not thread safe, but the underlying catalog resolver isn't either, so
   * we're not making it worse.
   */
  var alreadyResolvingXSD: Boolean = false

  /**
   * Called by SAX parser of the schema to resolve entities.
   *
   * Why the alreadyResolvingXSD flag?? it's because DFDL Schemas use the XSD namespace,
   * but want it to resolve to the DFDL subset schema, but the DFDL Subset Schema
   * uses the XSD Namespace, and does NOT want it to resolve to the DFDL subset,
   * but rather, to the regular XSD namespace. Actually its more than that.
   * We don't want to bother validating the DFDL Subset schemas every time
   * we load a file. So we special case the XSD namespace URI.
   *
   * Without this special case check, we'll recurse and stack overflow here.
   */
  def resolveEntity(ri: org.apache.xerces.xni.XMLResourceIdentifier): XMLInputSource = {
    val nsString = ri.getNamespace()
    val ns = NS(nsString)
    val literalSysId = ri.getLiteralSystemId()
    val baseURIString = ri.getBaseSystemId()

    if (ns == XMLUtils.XSD_NAMESPACE) {
      if (alreadyResolvingXSD) {
        log(LogLevel.Debug, "special resolved to null")
        return null
      }
    }
    val prior = alreadyResolvingXSD
    val res = try {
      alreadyResolvingXSD = (ns == XMLUtils.XSD_NAMESPACE)
      val optURI = resolveCommon(nsString, literalSysId, baseURIString)
      optURI match {
        case None => null
        case Some(uri) => {
          val xis = new XMLInputSource(null, uri.toString, null)
          xis
        }
      }
    } finally {
      alreadyResolvingXSD = prior
    }
    res
  }

  def resolveURI(uri: String, silent: Boolean): String = {
    init
    val optURI = resolveCommon(uri, null, null, silent)
    optURI match {
      case None => null
      case Some(uri) => uri.toString
    }
  }

  private def resolveCommon(nsURI: String, systemId: String, baseURIString: String, silent: Boolean = false): Option[URI] = {
    init
    if (nsURI == null && systemId == null && baseURIString == null) return None
    //
    // These checks are useful, because a common situation when the resolving logic
    // gets broken is that a schemaLocation like "xsd/foo.xsd" gets appended to 
    // the stem of a base URI, "file:/.../foo/xsd/bar.xsd", and it doubles-up the xsd directory.
    if (nsURI != null) Assert.usage(!nsURI.contains("xsd/xsd"))
    if (systemId != null) Assert.usage(!systemId.contains("xsd/xsd"))
    //
    // In case of the baseURIString, it's ok that Xerces has doubled up the xsd dir
    // in its attempt to determine the relative path, as that will simply not be found, b
    // but then the systemId alone (e.g., "xsd/foo.xsd")
    // will be tried on the class path.
    // if (baseURIString != null) Assert.usage(!baseURIString.contains("xsd/xsd")) // it can contain this!
    //
    if (!silent) log(LogLevel.Resolver, "nsURI = %s, baseURI = %s, systemId = %s", nsURI, baseURIString, systemId)
    val resolvedUri = delegate.resolveURI(nsURI)
    val resolvedSystem = delegate.resolveSystem(systemId)

    // An Include in a schema with a target namespace should resolve to the systemId and ignore the nsURI
    // because the nsURI will resolve to the including schema file.
    // This will cause the including schema to be repeatedly parsed resulting in a stack overflow.

    val resolvedId = {
      if (resolvedSystem != null && resolvedSystem != resolvedUri) {
        resolvedSystem
      } else if (resolvedUri != null && ((systemId == null) || (systemId != null && resolvedUri.endsWith(systemId)))) {
        resolvedUri
      } else
        null // We were unable to resolve the file based on the URI or systemID, so we will return null. 
    }

    val result = (resolvedId, systemId) match {
      case (null, null) => {
        // This happens now in some unit tests.
        // Assert.invariantFailed("resolvedId and systemId were null.")
        if (!silent)
          log(LogLevel.Resolver, "Unable to resolve.")
        return None
      }
      case (null, sysId) =>
        {
          val baseURI = if (baseURIString == null) None else Some(new URI(baseURIString))
          val optURI = Misc.getResourceRelativeOption(sysId, baseURI)
          optURI match {
            case Some(uri) => if (!silent) log(LogLevel.Resolver, "Found on classpath: %s.", uri)
            case None => if (!silent)
              log(LogLevel.Info, "Unable to resolve.")
          }
          optURI
        }
      case (resolved, _) => {
        if (!silent) log(LogLevel.Resolver, "Found via XML Catalog: %s.", resolved)
        Some(new URI(resolved))
      }
    }
    result
  }

  def resolveResource(type_ : String, nsURI: String, publicId: String, systemId: String, baseURIString: String): LSInput = {
    val optURI = resolveCommon(nsURI, systemId, baseURIString)
    optURI match {
      case None => null
      case Some(uri) => {
        val resourceAsStream =
          try {
            uri.toURL.openStream() // This will work.
          } catch {
            case _: java.io.IOException => Assert.invariantFailed("found resource but couldn't open")
          }
        val input = new Input(publicId, systemId, new BufferedInputStream(resourceAsStream))
        input.setBaseURI(uri.toString)
        input
      }
    }
  }

  override def resolveEntity(publicId: String, systemId: String) = {
    Assert.invariantFailed("resolveEntity3 - should not be called")
  }

  /**
   * We don't deal with DTDs at all. So this always returns null
   */
  def getExternalSubset(name: String, baseURI: String) = {
    log(LogLevel.Debug, "getExternalSubset: name = %s, baseURI = %s", name, baseURI)
    null
  }

  def resolveEntity(name: String, publicId: String, baseURI: String, systemId: String) = {
    Assert.invariantFailed("resolveEntity4 - should not be called")
  }
}

/**
 * catalog resolvers aren't thread safe. But they're also expensive stateful,
 * do I/O etc. so we really only want one per thread.
 */
object DFDLCatalogResolver {
  lazy val d = new ThreadLocal[DFDLCatalogResolver] {
    override def initialValue() = {
      new DFDLCatalogResolver()
    }
  }
  def get = d.get
}

class Input(var pubId: String, var sysId: String, var inputStream: BufferedInputStream)
  extends LSInput {

  var myBaseURI: String = null

  def getPublicId = pubId
  def setPublicId(publicId: String) = pubId = publicId
  def getBaseURI = myBaseURI
  def getByteStream = null
  def getCertifiedText = false
  def getCharacterStream = null
  def getEncoding = null
  def getStringData = {
    this.synchronized {
      val input: Array[Byte] = new Array[Byte](inputStream.available())
      inputStream.read(input)
      val contents = new String(input)
      contents
    }
  }
  def setBaseURI(baseURI: String) = {
    Assert.usage(!baseURI.contains("xsd/xsd"))
    myBaseURI = baseURI
  }
  def setByteStream(byteStream: InputStream) = {}
  def setCertifiedText(certifiedText: Boolean) = {}
  def setCharacterStream(characterStream: Reader) = {}
  def setEncoding(encoding: String) = {}
  def setStringData(stringData: String) = {}
  def getSystemId = sysId
  def setSystemId(systemId: String) = sysId = systemId
  def getInputStream: BufferedInputStream = inputStream
}

/**
 * Changes the parser behavior for <![CDATA[...]]]>
 * by scanning for this construct, and special casing it.
 */
trait CDataMixin { self: DFDLXMLLocationAwareAdapter =>
  val cdataStart = """\<\!\[CDATA\["""
  val cdataEnd = """\]\]\>"""
  val TextWithCData = ("""(.*)""" + cdataStart + """(.*)""" + cdataEnd + """(.*)""").r

  override def createText(text: String): Text = {

    if (!text.contains("<![CDATA[")) return Text(text)
    //
    // There's CDATA tags. They could be in the middle of the text
    // and their could be several.
    // They only extend until a matching "]]>" is found.
    text match {
      case TextWithCData(before, cdataPart, after) =>
        {

        }
        null
    }
  }
}
/**
 * An Adapter in SAX parsing is both an XMLLoader, and a handler of events.
 */
class DFDLXMLLocationAwareAdapter
  extends NoBindingFactoryAdapter
  //
  // We really want a whole XML Stack that is based on SAX2 DefaultHandler2 so that
  // we can handle CDATA elements right (we need events for them so we can 
  // create PCData objects from them directly. 
  //
  // The above is the only reason the TDML runner has to use the ConstructingParser
  // instead of calling this loader.
  //
  with Logging {

  var fileName: String = ""

  var saxLocator: org.xml.sax.Locator = _

  // Get location
  override def setDocumentLocator(locator: org.xml.sax.Locator) {
    //    println("setDocumentLocator line=%s col=%s sysID=%s, pubID=%s".format(
    //      locator.getLineNumber, locator.getColumnNumber,
    //      locator.getSystemId, locator.getPublicId))
    this.saxLocator = locator
    super.setDocumentLocator(locator)
  }

  case class Locator(line: Int, col: Int, sysID: String, pubID: String)

  // Without a trick, locators will always provide the end position of an element
  // and we want the start position.
  // With this trick, the line and column will be of the ending ">" of the
  // starting element tag.

  // startElement saves locator information on stack
  val locatorStack = new scala.collection.mutable.Stack[Locator]
  // endElement pops it off into here
  var elementStartLocator: Locator = _

  // create node then uses it.
  override def createNode(pre: String, label: String, attrs: MetaData, scope: NamespaceBinding, children: List[Node]): Elem = {

    // If we're the xs:schema node, then append attribute for _file_ as well.

    val nsURI = NS(scope.getURI(pre))
    val isXSSchemaNode = (label == "schema" && nsURI != NoNamespace &&
      (nsURI == XMLUtils.XSD_NAMESPACE))
    val isTDMLTestSuiteNode = (label == "testSuite" && nsURI != NoNamespace &&
      nsURI == XMLUtils.TDML_NAMESPACE)
    val isFileRootNode = isXSSchemaNode || isTDMLTestSuiteNode

    // augment the scope with the dafint namespace binding but only
    // for root nodes (to avoid clutter with the long xmlns:dafint="big uri")
    // and only if it isn't already there.
    //
    // The above would be a nice idea, but it requires that we are recursively
    // descending & ascending, carrying along that namespace definition so that
    // we have encountered the root first, etc.
    // Because Nodes are immutable, they don't point up at the scope toward
    // the parent. 
    // 
    // So we append this NS binding regardless of whether it is root or not.
    // Though we don't if it is already there.
    // 
    lazy val scopeWithDafInt =
      if (scope.getPrefix(XMLUtils.INT_NS) == null) // && isFileRootNode
        NamespaceBinding(XMLUtils.INT_PREFIX, XMLUtils.INT_NS, scope)
      else scope

    val haveFileName = isFileRootNode && fileName != ""

    val alreadyHasFile = attrs.get(XMLUtils.INT_NS, scopeWithDafInt, XMLUtils.FILE_ATTRIBUTE_NAME) != None

    // If there is already a _line_ attribute, then we're reloading something
    // that was probably converted back into a string and written out. 
    // The original line numbers are therefore the ones wanted, not any new
    // line numbers, so we don't displace any line numbers that already existed.

    val alreadyHasLine = attrs.get(XMLUtils.INT_NS, scopeWithDafInt, XMLUtils.LINE_ATTRIBUTE_NAME) != None
    val alreadyHasCol = attrs.get(XMLUtils.INT_NS, scopeWithDafInt, XMLUtils.COLUMN_ATTRIBUTE_NAME) != None
    Assert.invariant(alreadyHasLine == alreadyHasCol)

    val newScope =
      if (alreadyHasFile && alreadyHasLine && alreadyHasCol) scope
      else scopeWithDafInt

    val asIs = super.createNode(pre, label, attrs, newScope, children)

    val lineAttr =
      if (alreadyHasLine) Null
      else Attribute(XMLUtils.INT_PREFIX, XMLUtils.LINE_ATTRIBUTE_NAME, Text(elementStartLocator.line.toString), Null)
    val colAttr =
      if (alreadyHasCol) Null
      else Attribute(XMLUtils.INT_PREFIX, XMLUtils.COLUMN_ATTRIBUTE_NAME, Text(elementStartLocator.col.toString), Null)
    val fileAttr =
      if (alreadyHasFile || !haveFileName) Null
      else {
        val fileURIProtocolPrefix = if (fileName.startsWith("file:")) "" else "file:"
        Attribute(XMLUtils.INT_PREFIX, XMLUtils.FILE_ATTRIBUTE_NAME, Text(fileURIProtocolPrefix + fileName), Null)
      }

    // Scala XML note: The % operator creates a new element with updated attributes
    val res = asIs % lineAttr % colAttr % fileAttr
    // System.err.println("Create Node: " + res)
    res
  }

  override def startElement(uri: String, _localName: String, qname: String, attributes: org.xml.sax.Attributes): Unit = {
    // println("startElement")
    val loc = Locator(saxLocator.getLineNumber, saxLocator.getColumnNumber, saxLocator.getSystemId, saxLocator.getPublicId)
    locatorStack.push(loc)
    super.startElement(uri, _localName, qname, attributes)
  }

  override def endElement(uri: String, _localName: String, qname: String): Unit = {
    // println("endElement")
    elementStartLocator = locatorStack.pop
    super.endElement(uri, _localName, qname)
  }

}

/**
 * Manages the care and feeding of the Xerces schema-aware
 * XML parser that we use to do XML-Schema validation of the
 * files we are reading.
 *
 */
trait SchemaAwareLoaderMixin {
  // This is a single purpose trait
  self: DaffodilXMLLoader =>

  protected def doValidation: Boolean

  lazy val resolver = DFDLCatalogResolver.get

  override lazy val parser: SAXParser = {

    // val x = new com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl

    val f = SAXParserFactory.newInstance()
    f.setNamespaceAware(true)
    f.setFeature("http://xml.org/sax/features/namespace-prefixes", true)

    if (doValidation) {
      f.setFeature("http://xml.org/sax/features/validation", true)
      f.setFeature("http://apache.org/xml/features/validation/dynamic", true)
      f.setFeature("http://apache.org/xml/features/validation/schema", true)
      f.setFeature("http://apache.org/xml/features/validation/schema-full-checking", true)
      //      f.setValidating(true) // old style API? 
    }
    val parser = f.newSAXParser()
    val xr = parser.getXMLReader()
    xr.setContentHandler(this)
    //xr.setEntityResolver(resolver) // older API?? is this needed? Doesn't seem to hurt.
    xr.setProperty(
      "http://apache.org/xml/properties/internal/entity-resolver",
      resolver)
    parser
  }

  /**
   * UPA errors are detected by xerces if the schema-full-checking feature is
   * turned on, AND if you inform xerces that it is reading an XML Schema
   * (i.e., xsd).
   *
   * We are using it differently than this. We are loading DFDL Schemas,
   * which are being validated not as XML Schemas via xerces built-in
   * mechanisms, but as XML documents having a schema that we provide, which
   * enforces the subset of XML Schema that DFDL uses, etc.
   *
   * The problem is that checks like UPA aren't expressible in a
   * schema-for-DFDL-schemas. They are coded algorithmically right into Xerces.
   *
   * In order to get the UPA checks on DFDL schemas we have to do this in two
   * passes in order for things to be compatible with the TDMLRunner because
   * a TDML file is not a valid schema.
   *
   * First pass: The DFDL schema (or TDML file) is read as an XML document.
   * Second pass: We specially load up the DFDL schemas (in the case
   * of the TDMLRunner the DFDL Schema is extracted and loaded) treating them
   * as XML schemas, just to get these UPA diagnostics.  This is accomplished by
   * using the below SchemaFactory and SchemaFactory.newSchema calls.  The
   * newSchema call is what forces schema validation to take place.
   */
  protected val sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
  sf.setResourceResolver(resolver)
  sf.setErrorHandler(errorHandler)

  @deprecated("use input sources, not Files", "2014-11-21")
  def validateSchemaFile(file: File) = {
    sf.newSchema(file)
  }

  def validateSchema(source: InputSource) = {
    val saxSource = new SAXSource(source)
    sf.newSchema(saxSource)
  }

}

/**
 * Our modified XML loader.
 *
 * It validates as it loads. (If you ask it to via setting a flag.)
 *
 * It resolves xmlns URIs using an XML Catalog which
 * can be extended to include user-defined catalogs. By way of a
 * CatalogManger.properties file anywhere on the classpath.
 *
 * It adds diagnostic file, line number, column number information
 * to the nodes.
 *
 */
class DaffodilXMLLoader(val errorHandler: org.xml.sax.ErrorHandler)
  extends DFDLXMLLocationAwareAdapter
  with SchemaAwareLoaderMixin {

  def this() = this(RethrowSchemaErrorHandler)
  //
  // Controls whether we setup Xerces for validation or not.
  // 
  final var doValidation: Boolean = true

  def setValidation(flag: Boolean) {
    doValidation = flag
  }

  // everything interesting happens in the callbacks from the SAX parser
  // to the adapter.
  override def adapter = this

  // these load/loadFile overrides so we can grab the filename and give it to our
  // adapter that adds file attributes to the root XML Node.
  @deprecated("Use uri or input source, not File", "2014-11-21")
  override def loadFile(f: File) = {
    adapter.fileName = f.getAbsolutePath()

    val res = super.loadFile(f)
    res
  }

  @deprecated("Use uri or input source, not filename", "2014-11-21")
  override def loadFile(filename: String) = loadFile(new File(filename))

  def load(uri: URI) = {
    adapter.fileName = uri.toASCIIString
    val res = super.load(uri.toURL())
    res
  }

  //
  // This is the common routine called by all the load calls to actually 
  // carry out the loading of the schema.
  //
  override def loadXML(source: InputSource, p: SAXParser): Node = {
    // System.err.println("loadXML")
    val xr = p.getXMLReader()
    //    xr.setFeature("http://apache.org/xml/features/namespace-growth", true)
    xr.setErrorHandler(errorHandler)
    // parse file
    scopeStack.push(TopScope)
    // System.err.println("beginning parse")
    xr.parse(source)
    // System.err.println("ending parse")
    scopeStack.pop
    rootElem.asInstanceOf[Elem]
  }

}

/**
 * This is handy to keep around for debugging.
 */
object BasicStderrErrorHandler extends org.xml.sax.ErrorHandler {

  def warning(exception: SAXParseException) = {
    System.err.println("Warning " + exception.getMessage())
  }

  def error(exception: SAXParseException) = {
    System.err.println("Error: " + exception.getMessage())
  }
  def fatalError(exception: SAXParseException) = {
    System.err.println("Fatal: " + exception.getMessage())
  }
}

abstract class DFDLSchemaValidationException(cause: Throwable) extends Exception(cause)
case class DFDLSchemaValidationWarning(cause: Throwable) extends DFDLSchemaValidationException(cause)
case class DFDLSchemaValidationError(cause: Throwable) extends DFDLSchemaValidationException(cause)

object RethrowSchemaErrorHandler extends org.xml.sax.ErrorHandler {
  def warning(exception: SAXParseException) = {
    throw exception
  }

  def error(exception: SAXParseException) = {
    throw exception
  }

  def fatalError(exception: SAXParseException) = {
    throw exception
  }
}
