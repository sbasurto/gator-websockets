package gator.websockets.frames;

import gator.websockets.helpers.GatorWSHpkeSelfCheck;

public class GatorWSOutputFrameSelfCheck {
        public static void main(String[] args) throws Exception {
                byte[] medium = new GatorWSOutputFrame(GatorWSFrame.TEXT_FRAME).addData(new byte[32_768]);
                assert medium.length == 32_772;
                assert (medium[1] & 0xff) == 126;
                assert (medium[2] & 0xff) == 128 && medium[3] == 0;

                byte[] large = new GatorWSOutputFrame(GatorWSFrame.TEXT_FRAME).addData(new byte[65_536]);
                assert large.length == 65_546;
                assert (large[1] & 0xff) == 127;
                assert large[7] == 1 && large[8] == 0 && large[9] == 0;

                GatorWSHpkeSelfCheck.run();
        }
}
