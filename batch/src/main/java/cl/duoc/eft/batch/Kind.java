package cl.duoc.eft.batch;

public enum Kind {
    DAILY("movimientos_financieros_diarios.csv", "id,fecha,monto,tipo", "movimientosJob"),
    INTEREST("intereses_trimestrales.csv", "cuenta_id,nombre,saldo,edad,tipo", "interesesJob"),
    ANNUAL("estados_financieros_anuales.csv", "cuenta_id,fecha,transaccion,monto,descripcion", "estadosJob");

    public final String filename;
    public final String header;
    public final String jobName;

    Kind(String filename, String header, String jobName) {
        this.filename = filename;
        this.header = header;
        this.jobName = jobName;
    }
}
