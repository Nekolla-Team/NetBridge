package team.nekolla.netbridge.nativebridge;

@FunctionalInterface
public interface NativeEventListener {

    void onEvent(NativeEvent event);

}
