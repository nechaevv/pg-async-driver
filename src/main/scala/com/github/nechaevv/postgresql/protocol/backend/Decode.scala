package com.github.nechaevv.postgresql.protocol.backend

/**
  * Created by vn on 17.07.16.
  */
object Decode {
  def apply(p: Packet): BackendMessage = {
    val i = p.payload.iterator
    p.messageType match {
      case 'E' => new ErrorMessage(i)
      case 'N' => new NoticeResponse(i)
      case 'A' => new NotificationResponse(i)
      case 'R' =>
        i.getInt match {
          case 0 => AuthenticationOk
          case 2 => AuthenticationKerberosV5
          case 3 => AuthenticationCleartextPassword
          case 5 => new AuthenticationMD5Password(i)
          case 6 => AuthenticationSCMCredential
          case 7 => AuthenticationGSS
          case 8 => AuthenticationSSPI
          case 9 => new AuthenticationGSSContinue(i, p.payload.length)
        }
      case 'C' => new CommandComplete(i)
      case 'T' => new RowDescription(i)
      case 't' => new ParameterDescription(i)
      case 'D' => new DataRow(i)
      case 'Z' => new ReadyForQuery(i)
      case 'I' => EmptyQueryResponse
      case 'n' => NoData
      case 's' => PortalSuspended
      case 'S' => new ParameterStatus(i)
      case 'K' => new BackendKeyData(i)
      case '1' => ParseComplete
      case '2' => BindComplete
      case '3' => CloseComplete

      case t => UnknownMessage(t.toChar, p.payload)
    }
  }
}