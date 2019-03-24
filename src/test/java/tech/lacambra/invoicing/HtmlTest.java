package tech.lacambra.invoicing;

import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.pdf.PdfWriter;
import com.itextpdf.tool.xml.XMLWorkerHelper;
import org.junit.Test;

import java.io.FileOutputStream;
import java.io.IOException;

public class HtmlTest {

  @Test
  public void generatePDFFromHTML() throws IOException, DocumentException {
    String filename = "out.pdf";
    Document document = new Document();
    PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream("/Users/albertlacambra/git/lacambra.tech/invoicing/target/html.pdf"));
    document.open();
    XMLWorkerHelper.getInstance().parseXHtml(writer, document, getClass().getClassLoader().getResourceAsStream("file.html"));
    document.close();
  }
}
