package apps.java.loref;

public interface MotionCommListener {

    void onNewFrame(String cameraID, byte[] frameData, String destination);

    void statusChanged(String cameraID);

}
