package tech.lacambra.invoicing;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.stream.JsonCollectors;
import javax.mail.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class AddressExtractor {

  private String filterEmail;

  public static void main(String[] args) throws IOException {
    new AddressExtractor().run();
  }

  void run() throws IOException {
    Properties props = new Properties();
    props.load(new FileInputStream(new File(System.getenv("EMAIL_PROPS_FILE"))));

    String host = props.getProperty("host");
    String username = props.getProperty("username");
    String password = props.getProperty("password");
    filterEmail = props.getProperty("filterEmail");

    //String provider  = "pop3";
    String provider = "imap";

    try {
      //Connect to the server
      Session session = Session.getDefaultInstance(props, null);
      Store store = session.getStore(provider);
      store.connect(host, username, password);

      //open the inbox folder
      Folder inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_ONLY);

      // get a list of javamail messages as an array of messages
      List<Message> messages = Arrays.asList(inbox.getMessages());

      messages.stream().filter(this::isInvoiceEmail).map(this::msgToJsonObject).forEach(System.out::println);
      inbox.close(false);
      store.close();

    } catch (NoSuchProviderException nspe) {
      System.err.println("invalid provider name");
    } catch (MessagingException me) {
      System.err.println("messaging exception");
      me.printStackTrace();
    }
  }

  private JsonObject msgToJsonObject(Message message) {
    try {
      return Json.createObjectBuilder()
          .add("from", recipientsToString(message))
          .add("to", Stream.of(message.getFrom()).map(this::addressToStr).map(Object::toString).collect(Collectors.joining(", ")))
          .add("subject", message.getSubject())
          .add("messageNumber", message.getMessageNumber())
          .build();

    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }
  }

  private boolean isInvoiceEmail(Message message) {
    try {
      return Arrays.asList(message.getAllRecipients()).toString().contains(filterEmail);
    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }
  }

  private JsonArray recipientsToString(Message message) {


    try {
      return Stream.of(message.getAllRecipients()).map(this::addressToStr)
          .filter(jsonObject -> jsonObject.getString("txt").contains("invoice@lacambra.tech"))
          .collect(JsonCollectors.toJsonArray());

    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }


  }


  JsonObject addressToStr(Address address) {
    return Json.createObjectBuilder().add("type", address.getType()).add("txt", address.toString()).build();
  }


  private String getFrom(Message msg) throws MessagingException {
    String from = "";
    Address a[] = msg.getAllRecipients();
    if (a == null) return null;
    for (int i = 0; i < a.length; i++) {
      Address address = a[i];
      from = from + address.toString();
    }

    return from;
  }

  private String removeQuotes(String stringToModify) {
    int indexOfFind = stringToModify.indexOf(stringToModify);
    if (indexOfFind < 0) return stringToModify;

    StringBuffer oldStringBuffer = new StringBuffer(stringToModify);
    StringBuffer newStringBuffer = new StringBuffer();
    for (int i = 0, length = oldStringBuffer.length(); i < length; i++) {
      char c = oldStringBuffer.charAt(i);
      if (c == '"' || c == '\'') {
        // do nothing
      } else {
        newStringBuffer.append(c);
      }

    }
    return new String(newStringBuffer);
  }

}
