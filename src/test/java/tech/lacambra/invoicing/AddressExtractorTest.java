package tech.lacambra.invoicing;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Date;

public class AddressExtractorTest {

  AddressExtractor cut;

  @Before
  public void setUp() throws Exception {

    cut = new AddressExtractor();
  }

  @Test
  public void getPeriod() {

    Assert.assertEquals(LocalDate.now().getMonth() + "_" + LocalDate.now().getYear(), cut.getPeriodName(new Date()));

  }
}