package com.onlineshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class WalletServiceTest {
    @Test void exactDecimalMoney() {
        assertEquals(new BigDecimal("10.00"),WalletService.money("10",false));
        assertEquals(new BigDecimal("0.01"),WalletService.money("0.01",false));
        assertEquals(new BigDecimal("-50.25"),WalletService.money("-50.25",true));
        assertEquals(new BigDecimal("99999999.99"),WalletService.money("99999999.99",true));
    }
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings={"0","0.00","-0.00","-1","1.001","1e2","NaN","Infinity","100000000","1,000"," 10","10 ","+10",".5","10.","￥10","中文"})
    void rechargeRejectsInvalidAmounts(String value) {
        assertThrows(ResponseStatusException.class,()->WalletService.money(value,false));
    }
    @ParameterizedTest @ValueSource(strings={"-0","-0.00","-1.001","-100000000","--10","+1"})
    void signedMoneyStillRejectsInvalidAmounts(String value) {
        assertThrows(ResponseStatusException.class,()->WalletService.money(value,true));
    }
}
