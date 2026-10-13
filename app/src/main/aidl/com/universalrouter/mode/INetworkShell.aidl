package com.universalrouter.mode;

// Servicio que corre con permisos de shell (UID 2000) a través de Shizuku.
interface INetworkShell {
    // Código reservado por Shizuku para destruir el servicio.
    void destroy() = 16777114;

    // Tipos de red permitidos (bitmask de TelephonyManager.NETWORK_TYPE_BITMASK_*).
    long getAllowedNetworkTypes(int subId) = 1;

    boolean setAllowedNetworkTypes(int subId, long types) = 2;
}
