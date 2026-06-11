package com.qwertyblob.every1luvs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:every1luvs_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.sql.init.mode=never",
		"app.auth.token-secret=test-secret-that-is-at-least-32-chars!!"
})
class Every1luvsApplicationTests {

	@Test
	void contextLoads() {
	}

}
