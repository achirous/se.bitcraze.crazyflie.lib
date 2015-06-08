package se.bitcraze.crazyflie.lib.log;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import se.bitcraze.crazyflie.lib.toc.VariableType;

public class LogTocElementTest {

    @Test
    public void testLogTocElement() {
        // First two !? bytes of payload need to be stripped away?

        //FLOAT
        //original byte: 80,0,0,7,112,109,0,118,98,97,116,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0
        byte[] id0 = new byte[] {0,7,112,109,0,118,98,97,116,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};

        LogTocElement lteId00 = new LogTocElement(id0);

        assertEquals("pm", lteId00.getGroup());
        assertEquals("vbat", lteId00.getName());
        assertEquals("pm.vbat", lteId00.getCompleteName());
        assertEquals(VariableType.FLOAT, lteId00.getCtype());
        assertEquals(0, lteId00.getIdent());                              //ID can change after firmware update

        //INT8_T
        //original byte: 80,0,1,4,112,109,0,115,116,97,116,101,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        byte[] id1 = new byte[] {1,4,112,109,0,115,116,97,116,101,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,};

        LogTocElement lteId01 = new LogTocElement(id1);

        assertEquals("pm", lteId01.getGroup());
        assertEquals("state", lteId01.getName());
        assertEquals("pm.state", lteId01.getCompleteName());
        assertEquals(VariableType.INT8_T, lteId01.getCtype());
        assertEquals(1, lteId01.getIdent());                              //ID can change after firmware update

        //UINT16_T
        //original byte: 80,0,20,2,115,116,97,98,105,108,105,122,101,114,0,116,104,114,117,115,116,0,0,0,0,0,0,0,0,0,0,0,
        byte[] id20 = new byte[] {20,2,115,116,97,98,105,108,105,122,101,114,0,116,104,114,117,115,116,0,0,0,0,0,0,0,0,0,0,0,};

        LogTocElement lteId20 = new LogTocElement(id20);

        assertEquals("stabilizer", lteId20.getGroup());
        assertEquals("thrust", lteId20.getName());
        assertEquals("stabilizer.thrust", lteId20.getCompleteName());
        assertEquals(VariableType.UINT16_T, lteId20.getCtype());
        assertEquals(20, lteId20.getIdent());                              //ID can change after firmware update

        //INT32_T
        //original byte: 80,0,13,6,109,111,116,111,114,0,109,52,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        byte[] id13 = new byte[] {13,6,109,111,116,111,114,0,109,52,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,};

        LogTocElement lteId13 = new LogTocElement(id13);

        assertEquals("motor", lteId13.getGroup());
        assertEquals("m4", lteId13.getName());
        assertEquals("motor.m4", lteId13.getCompleteName());
        assertEquals(VariableType.INT32_T, lteId13.getCtype());
        assertEquals(13, lteId13.getIdent());                              //ID can change after firmware update
    }

}