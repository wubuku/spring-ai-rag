package com.springairag.demo;

import com.springairag.core.service.RagChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DemoController unit test — uses @WebMvcTest to load only the web layer,
 * mocks the downstream RagChatService dependency.
 */
@WebMvcTest(controllers = DemoController.class)
@ActiveProfiles("test")
class DemoControllerTest {

    @MockBean
    private RagChatService ragChatService;

    @Autowired
    private DemoController demoController;

    @Test
    void controllerLoads() {
        assertNotNull(demoController);
        assertEquals(DemoController.class, demoController.getClass());
    }
}
